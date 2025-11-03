const path = require("path");
const webpack = require("webpack");
const HtmlWebpackPlugin = require("html-webpack-plugin");
const CopyWebpackPlugin = require("copy-webpack-plugin");
let dotenv;
try { dotenv = require("dotenv"); } catch (e) { dotenv = null; }
const MiniCssExtractPlugin = require("mini-css-extract-plugin");

module.exports = (env = {}) => {
  const isProd = Boolean(env.production) || process.env.NODE_ENV === "production";
  const mode = isProd ? "production" : "development";

  // Load environment variables from .env files if dotenv is available
  if (dotenv) {
    const envName = process.env.APP_ENV || (isProd ? "prod" : "local");
    const pathLocal = path.resolve(__dirname, `.env.${envName}`);
    dotenv.config({ path: pathLocal });
    dotenv.config(); // fallback to .env
  }

  return {
    mode,
    target: "web",
    entry: path.resolve(__dirname, "src/main.tsx"),
    output: {
      path: path.resolve(__dirname, "dist"),
      filename: isProd ? "assets/js/[name].[contenthash].js" : "assets/js/[name].js",
      chunkFilename: isProd ? "assets/js/[name].[contenthash].chunk.js" : "assets/js/[name].chunk.js",
      publicPath: "/",
      clean: true
    },
    resolve: {
      extensions: [".ts", ".tsx", ".js", ".jsx", ".json"]
    },
    devtool: isProd ? "source-map" : "eval-cheap-module-source-map",
    module: {
      rules: [
        {
          test: /\.[jt]sx?$/,
          exclude: /node_modules/,
          use: {
            loader: "ts-loader",
            options: {
              transpileOnly: false
            }
          }
        },
        {
          test: /\.css$/i,
          use: [
            isProd ? MiniCssExtractPlugin.loader : "style-loader",
            {
              loader: "css-loader",
              options: {
                importLoaders: 1
              }
            },
            {
              loader: "postcss-loader",
              options: {
                postcssOptions: {
                  plugins: [
                    require("tailwindcss"),
                    require("autoprefixer")
                  ]
                }
              }
            }
          ]
        },
        {
          test: /\.(png|jpe?g|gif|svg|webp)$/i,
          type: "asset",
          parser: {
            dataUrlCondition: {
              maxSize: 10 * 1024
            }
          },
          generator: {
            filename: "assets/images/[name][contenthash][ext]"
          }
        },
        {
          test: /\.(woff2?|ttf|eot|otf)$/i,
          type: "asset/resource",
          generator: {
            filename: "assets/fonts/[name][contenthash][ext]"
          }
        }
      ]
    },
    plugins: [
      new HtmlWebpackPlugin({
        template: path.resolve(__dirname, "public/index.html"),
        minify: isProd
      }),
      // Copy static assets from public/ (favicons, robots.txt, etc.) to dist/ in production
      isProd &&
        new CopyWebpackPlugin({
          patterns: [
            {
              from: path.resolve(__dirname, "public"),
              to: ".",
              globOptions: {
                ignore: ["**/index.html"],
              },
              noErrorOnMissing: true,
            },
          ],
        }),
      isProd &&
        new MiniCssExtractPlugin({
          filename: "assets/css/[name].[contenthash].css",
          chunkFilename: "assets/css/[name].[contenthash].chunk.css"
        }),
      new webpack.DefinePlugin({
        "process.env.NODE_ENV": JSON.stringify(mode),
        // Leave undefined when not set so runtime can fall back to window.location.origin
        "process.env.TRENDS_API_URL": JSON.stringify(process.env.TRENDS_API_URL || ""),
        "process.env.VITE_ENV_LABEL": JSON.stringify(process.env.VITE_ENV_LABEL ?? "")
      })
    ].filter(Boolean),
    devServer: {
      static: {
        directory: path.resolve(__dirname, "public")
      },
  historyApiFallback: true,
  port: Number(process.env.PORT) || 5176,
      hot: true,
      client: {
        overlay: true
      }
    },
    optimization: {
      splitChunks: {
        chunks: "all"
      },
      runtimeChunk: "single"
    },
    performance: {
      hints: false
    }
  };
};
